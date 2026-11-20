package com.expenses.app.ui.screens.split

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.expenses.app.groups.data.GroupKind
import com.expenses.app.groups.data.GroupText
import com.expenses.app.groups.data.GroupsRepository
import com.expenses.app.groups.data.MemberEntity
import com.expenses.app.groups.data.Person
import com.expenses.app.ui.nav.AddedFriend
import com.expenses.app.ui.state.keepForm
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * What Choose people (design 02-everyday/12-choose-people, 48-several-friends) shows.
 *
 * @param query the search as typed.
 * @param groups the person's groups that match the search.
 * @param friends the people they split with that match the search, and friends added here.
 * @param selectedGroupId the group switched on, or null.
 * @param selectedFriends the friends switched on, by [Person.key], in the order they were picked.
 * @param working true while a friend's direct split is found or made.
 */
@Immutable
data class ChoosePeopleUiState(
    val query: String,
    val groups: List<ChoiceGroup>,
    val friends: List<ChoiceFriend>,
    val selectedGroupId: String?,
    val selectedFriends: List<String>,
    val working: Boolean,
) {
    /** True when two or more friends are on: a split goes to one group or one friend, so "Make a group" shows. */
    val severalFriends: Boolean get() = selectedFriends.size > 1

    /** True when "Done" can be tapped: one group, or one friend. */
    val canFinish: Boolean get() = !working && (selectedGroupId != null || selectedFriends.size == 1)
}

/**
 * A group on Choose people.
 *
 * @param id the group.
 * @param name its name.
 * @param memberCount people in it, the person included ("3 people").
 */
@Immutable
data class ChoiceGroup(val id: String, val name: String, val memberCount: Int)

/**
 * A friend on Choose people.
 *
 * @param key the person ([Person.key]); a friend added here has a key of their own.
 * @param name their name.
 * @param onApp true for "On SumBuddy", false for "Gets a WhatsApp link".
 */
@Immutable
data class ChoiceFriend(val key: String, val name: String, val onApp: Boolean)

/** Everything the person can do on Choose people. */
sealed interface ChoosePeopleEvent {
    /** The search changed to [text]. */
    data class QueryChanged(val text: String) : ChoosePeopleEvent

    /** A group's switch was turned [on]. */
    data class GroupSwitched(val groupId: String, val on: Boolean) : ChoosePeopleEvent

    /** A friend's switch was turned [on]. */
    data class FriendSwitched(val key: String, val on: Boolean) : ChoosePeopleEvent

    /** "Done": hand the one group or friend back. */
    data object Done : ChoosePeopleEvent

    /** "Make a group" with the friends switched on. Handled by the caller. */
    data object MakeGroup : ChoosePeopleEvent

    /** "Add someone". Handled by the caller. */
    data object AddSomeone : ChoosePeopleEvent

    /** Add friend handed back [friend]: they join Friends, switched on. */
    data class FriendAdded(val friend: AddedFriend) : ChoosePeopleEvent

    /** New group made the group [groupId] from the friends picked: it is the choice. */
    data class GroupCreated(val groupId: String) : ChoosePeopleEvent

    /** The back button. Handled by the caller. */
    data object Back : ChoosePeopleEvent
}

/**
 * Choose people for Split it: one group, or one friend (whose direct split is found or made with
 * `GroupsRepository.directGroupWith`). Several friends get "Make a group" (New group with them filled in); the group it
 * makes is the choice. "Add someone" (Add friend) adds a friend who is in no group yet, switched on, so a person with
 * nobody known can make their first split.
 *
 * @param selectedGroupId the group chosen on Split it so far, switched on at the start (a direct split switches on
 * its friend).
 * @param groups groups and the people in them.
 * @param savedState where the search and the switches are kept while Android stops the app in the background.
 */
class ChoosePeopleViewModel(
    selectedGroupId: String?,
    private val groups: GroupsRepository,
    savedState: SavedStateHandle,
) : ViewModel() {
    private data class Local(
        val query: String = "",
        val selectedGroupId: String? = null,
        val selectedFriends: List<String> = emptyList(),
        val added: List<AddedFriend> = emptyList(),
        val working: Boolean = false,
    )

    private val local = MutableStateFlow(
        Local(
            query = savedState[QUERY_KEY] ?: "",
            selectedGroupId = savedState[GROUP_KEY],
            selectedFriends = savedState.get<String>(FRIENDS_KEY)?.let { decode(it, KEYS) }.orEmpty(),
            added = savedState.get<String>(ADDED_KEY)?.let { decode(it, ADDED) }.orEmpty(),
        ),
    )

    private val chosenGroup = MutableStateFlow<String?>(null)

    /** The group to hand back to Split it, once chosen. */
    val chosen: StateFlow<String?> = chosenGroup.asStateFlow()

    init {
        val restored = savedState.contains(QUERY_KEY)
        savedState.keepForm(viewModelScope, local) {
            set(QUERY_KEY, it.query)
            set(GROUP_KEY, it.selectedGroupId)
            set(FRIENDS_KEY, Codec.encodeToString(KEYS, it.selectedFriends))
            set(ADDED_KEY, Codec.encodeToString(ADDED, it.added))
        }
        if (!restored && selectedGroupId != null) viewModelScope.launch { preselect(selectedGroupId) }
    }

    /** The people the person already splits with, as last read; null until read. */
    private val known: StateFlow<List<Person>?> =
        groups.observeKnownPeople().stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    /** The screen's state. */
    val state: StateFlow<ChoosePeopleUiState> = combine(groups.observeGroups(), known.filterNotNull(), local) { named, known, local ->
        val friends = friendsOf(known, local.added)
        val query = local.query.trim()
        ChoosePeopleUiState(
            query = local.query,
            // As on Split it: a group where everyone else has left has nobody to split with and is not offered,
            // unless it is the one already chosen.
            groups = named
                .filter { (it.memberCount > 1 || it.id == local.selectedGroupId) && matches(query, it.name, null) }
                .map { ChoiceGroup(it.id, it.name, it.memberCount) },
            friends = friends.filter { matches(query, it.name, it.phone) }.map { ChoiceFriend(it.key, it.name, it.onApp) },
            selectedGroupId = local.selectedGroupId,
            selectedFriends = local.selectedFriends.filter { key -> friends.any { it.key == key } },
            working = local.working,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), EMPTY)

    /** Handles what the person did. MakeGroup, AddSomeone and Back are handled by the caller and ignored here. */
    fun onEvent(event: ChoosePeopleEvent) {
        when (event) {
            is ChoosePeopleEvent.QueryChanged -> local.update { it.copy(query = event.text) }
            // One group, or friends: switching one kind on switches the other kind off.
            is ChoosePeopleEvent.GroupSwitched -> local.update {
                if (event.on) it.copy(selectedGroupId = event.groupId, selectedFriends = emptyList()) else it.copy(selectedGroupId = null)
            }
            is ChoosePeopleEvent.FriendSwitched -> local.update {
                if (event.on) {
                    it.copy(selectedGroupId = null, selectedFriends = (it.selectedFriends - event.key) + event.key)
                } else {
                    it.copy(selectedFriends = it.selectedFriends - event.key)
                }
            }
            is ChoosePeopleEvent.FriendAdded -> friendAdded(event.friend)
            is ChoosePeopleEvent.GroupCreated -> chosenGroup.value = event.groupId
            ChoosePeopleEvent.Done -> finish()
            ChoosePeopleEvent.MakeGroup, ChoosePeopleEvent.AddSomeone, ChoosePeopleEvent.Back -> Unit
        }
    }

    /**
     * The keys of the friends switched on whom New group knows (`observeKnownPeople`), in the order they were picked.
     * Friends added here who are in no group yet are in [pickedNewFriends] instead.
     */
    fun pickedKeys(): List<String> {
        val knownKeys = known.value.orEmpty().mapTo(HashSet()) { it.key }
        return state.value.selectedFriends.filter { it in knownKeys }
    }

    /**
     * The friends switched on who came from Add friend here and are in no group yet, in the order they were picked:
     * New group cannot find them by key, so they are handed over as they were added.
     */
    fun pickedNewFriends(): List<AddedFriend> {
        val knownKeys = known.value.orEmpty().mapTo(HashSet()) { it.key }
        val added = local.value.added.associateBy(::addedKey)
        return state.value.selectedFriends.filter { it !in knownKeys }.mapNotNull { added[it] }
    }

    private fun friendAdded(friend: AddedFriend) {
        viewModelScope.launch {
            val known = groups.observeKnownPeople().first()
            val phone = GroupText.normalizePhone(friend.phone)
            // Someone already known by that phone number is the same person.
            val same = phone?.let { p -> known.firstOrNull { it.phone == p } }
            local.update { current ->
                val added = if (same != null) current.added else current.added.filterNot { addedKey(it) == addedKey(friend) } + friend
                val key = same?.key ?: addedKey(friend)
                current.copy(added = added, selectedGroupId = null, selectedFriends = (current.selectedFriends - key) + key)
            }
        }
    }

    private fun finish() {
        val current = state.value
        if (!current.canFinish) return
        current.selectedGroupId?.let {
            chosenGroup.value = it
            return
        }
        val key = current.selectedFriends.single()
        local.update { it.copy(working = true) }
        viewModelScope.launch {
            val person = friendsOf(groups.observeKnownPeople().first(), local.value.added).firstOrNull { it.key == key }
            val id = person?.let {
                try {
                    groups.directGroupWith(it)
                } catch (_: IllegalStateException) {
                    null // Signed out meanwhile: Split it's sign-in gate takes over.
                } catch (_: IllegalArgumentException) {
                    null
                }
            }
            local.update { it.copy(working = false) }
            if (id != null) chosenGroup.value = id
        }
    }

    private suspend fun preselect(groupId: String) {
        val detail = groups.observeGroup(groupId).first() ?: return
        if (detail.group.kind == GroupKind.GROUP) {
            local.update { it.copy(selectedGroupId = groupId) }
            return
        }
        val other = detail.members.firstOrNull { !it.isMe }?.member ?: return
        val key = keyOf(other, groups.observeKnownPeople().first()) ?: return
        local.update { it.copy(selectedFriends = listOf(key)) }
    }

    /** [member] as Choose people lists them: the known person with their app account, phone number, or name. */
    private fun keyOf(member: MemberEntity, known: List<Person>): String? =
        known.firstOrNull { member.userId != null && it.userId == member.userId }?.key
            ?: known.firstOrNull { member.phone != null && it.phone == member.phone }?.key
            ?: known.firstOrNull { it.key == member.id }?.key
            ?: known.firstOrNull {
                member.userId == null && member.phone == null && it.userId == null && it.phone == null &&
                    it.name.equals(member.name, ignoreCase = true)
            }?.key

    private fun friendsOf(known: List<Person>, added: List<AddedFriend>): List<Person> =
        known + added.map { Person(addedKey(it), it.name.trim(), userId = null, phone = GroupText.normalizePhone(it.phone)) }
            .filter { friend -> known.none { it.key == friend.key } }

    private fun matches(query: String, name: String, phone: String?): Boolean {
        if (query.isEmpty()) return true
        if (name.contains(query, ignoreCase = true)) return true
        val digits = query.filter(Char::isDigit)
        return digits.length >= MIN_PHONE_DIGITS && phone?.filter(Char::isDigit)?.contains(digits) == true
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val MIN_PHONE_DIGITS = 3
        const val QUERY_KEY = "choose_query"
        const val GROUP_KEY = "choose_group"
        const val FRIENDS_KEY = "choose_friends"
        const val ADDED_KEY = "choose_added"
        val KEYS = ListSerializer(String.serializer())
        val ADDED = ListSerializer(AddedFriend.serializer())
        val Codec = Json { ignoreUnknownKeys = true }
        val EMPTY = ChoosePeopleUiState("", emptyList(), emptyList(), null, emptyList(), working = false)

        /**
         * A friend added here: their phone number as the app writes it (as a known friend's key), else "name:" and
         * their name in lower case, as a friend with neither an app account nor a phone number is known only by name.
         */
        fun addedKey(friend: AddedFriend): String =
            GroupText.normalizePhone(friend.phone) ?: ("name:" + friend.name.trim().lowercase())

        fun <T> decode(text: String, serializer: kotlinx.serialization.KSerializer<T>): T? =
            runCatching { Codec.decodeFromString(serializer, text) }.getOrNull()
    }
}
