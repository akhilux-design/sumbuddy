package com.expenses.app.ui.screens.groups

import androidx.compose.runtime.Immutable
import com.expenses.app.ui.components.AvatarColor

/**
 * Everything the Groups tab shows. Each design state of the tab is one case: the list (02-everyday/20, and 44 when
 * the person owes overall), the first-run empty state (21), the placeholders while loading (22), Could not load (the
 * card of 23) and Sign in (39).
 */
@Immutable
sealed interface GroupsUiState {

    /**
     * The groups and friends the person shares payments with, with the overall balance on top.
     *
     * @param groups the person's named groups, in the order they are listed.
     * @param friends the people the person has a balance with, over every group, largest first.
     * @param overallPaise the sum of the person's net in every group and direct split, in paise ("OVERALL YOU ARE
     * OWED"; docs/server.md 6.4). Above zero means others owe the person.
     * @param groupCount how many named groups are listed ("across 3 groups").
     */
    @Immutable
    data class Loaded(
        val groups: List<GroupBalance>,
        val friends: List<FriendBalance>,
        val overallPaise: Long = groups.sumOf { it.balancePaise },
        val groupCount: Int = groups.size,
    ) : GroupsUiState

    /**
     * The person is in no group yet and has no balance with anyone: the server said so, or this phone holds only
     * splits with one friend that are all settled. Never shown before the server's list of groups has arrived.
     */
    data object Empty : GroupsUiState

    /** The groups are being fetched; grey placeholders stand where the card and rows will be. */
    data object Loading : GroupsUiState

    /**
     * The groups could not be fetched and none is on this phone, so the tab cannot say whether the person has any:
     * the card of 02-everyday/23 with Try again, in place of "No groups yet".
     */
    data object CouldNotLoad : GroupsUiState

    /** Nobody is signed in: "Split with friends" with Continue with Google (02-everyday/39). */
    data object NotSignedIn : GroupsUiState
}

/**
 * One group in the list with the person's balance in it.
 *
 * @param id stable id of the group, sent back when the row is tapped.
 * @param name name of the group, for example "Flat 302". Its first letter is shown in the avatar.
 * @param memberCount how many people are in the group, the person included.
 * @param balancePaise the person's balance in the group in paise: above zero the group owes the person,
 * below zero the person owes the group, zero when all is settled.
 * @param avatarColor colour of the circle behind the group's initial.
 */
@Immutable
data class GroupBalance(
    val id: String,
    val name: String,
    val memberCount: Int,
    val balancePaise: Long,
    val avatarColor: AvatarColor,
)

/**
 * One friend under Friends, with the balance between them over every group.
 *
 * @param id stable id of the friend: their user id, else their phone number, else their member id.
 * @param name the friend's name, for example "Rahul". Its first letter is shown in the avatar.
 * @param balancePaise the balance in paise: above zero the friend owes the person, below zero the person owes
 * the friend, zero when all is settled.
 * @param avatarColor colour of the circle behind the friend's initial.
 * @param groupId the direct split with this friend on this phone, opened when the row is tapped; null when every
 * balance with them is in named groups, and the row is then not tappable.
 */
@Immutable
data class FriendBalance(
    val id: String,
    val name: String,
    val balancePaise: Long,
    val avatarColor: AvatarColor,
    val groupId: String? = null,
)

/** Something the person did on the Groups tab. */
sealed interface GroupsEvent {
    /** Tapped "+ New" in the header or "New group" in the empty state. */
    data object NewGroupClicked : GroupsEvent

    /**
     * Tapped a group in the list.
     *
     * @param groupId the [GroupBalance.id] of that group.
     */
    data class GroupClicked(val groupId: String) : GroupsEvent

    /**
     * Tapped a friend that has a direct split on this phone.
     *
     * @param groupId the [FriendBalance.groupId] of that friend: the direct split to open.
     */
    data class FriendClicked(val groupId: String) : GroupsEvent

    /** Tapped "Continue with Google" while signed out. */
    data object ContinueWithGoogle : GroupsEvent

    /** Tapped "Try again" on Could not load. */
    data object TryAgainClicked : GroupsEvent
}
