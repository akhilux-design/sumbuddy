package com.expenses.app.ui.screens.grouppage

import androidx.compose.runtime.Immutable

/** Everything Group settings shows (design 02-everyday/25-group-settings, 45-not-owner, 46-friend-s-link). */
@Immutable
sealed interface GroupSettingsUiState {
    /** The group is being read. */
    data object Loading : GroupSettingsUiState

    /** The group is no longer on this phone (left, deleted, or removed by a sync): the screen leaves. */
    data object Gone : GroupSettingsUiState

    /**
     * Group settings.
     *
     * @param groupName the group's name as the confirm dialogs write it (a direct split: the friend's name).
     * @param isDirect true for a direct split: no name, no Add someone and no Delete group.
     * @param nameField the text in "Group name".
     * @param nameError true when the name left blank is not saved ("That does not look right.").
     * @param people the person first, then the owner when it is someone else (45), then the other active members.
     * @param canLeave true while the person's net is 0: Leave group is tappable.
     * @param delete the Delete group row, or null for none (a direct split, or an owner no longer in the group).
     * @param notice the banner an online-only action left at the top of the body, or null.
     * @param confirm the confirm dialog open, or null.
     * @param sheet the friend's link sheet open (46), or null.
     * @param busy true while an online-only action waits for the server: further taps are ignored.
     */
    @Immutable
    data class Shown(
        val groupName: String,
        val isDirect: Boolean,
        val nameField: String,
        val nameError: Boolean = false,
        val people: List<PersonRow>,
        val canLeave: Boolean,
        val delete: DeleteRow?,
        val notice: GroupNotice? = null,
        val confirm: SettingsConfirm? = null,
        val sheet: FriendSheet? = null,
        val busy: Boolean = false,
    ) : GroupSettingsUiState
}

/** How a person under People is described. */
enum class PersonKind {
    /** The person: "You". */
    You,

    /** An app user: "On SumBuddy". */
    OnApp,

    /**
     * A friend without the app and no link from this phone: "Not on SumBuddy". Tapping opens 46 with "No link from this
     * phone", a way to send one and Turn off link (another member may have sent one).
     */
    Friend,

    /** A friend without the app with a link this phone made: "Not on SumBuddy · link sent". Tapping opens 46. */
    FriendLinkSent,
}

/**
 * One person under People: a Setting Row with the 3D user icon.
 *
 * @param memberId the member.
 * @param name their name.
 * @param kind how they are described.
 * @param isOwner true for the owner's row, which carries the "Owner" tag where Choose people has its switch.
 */
@Immutable
data class PersonRow(val memberId: String, val name: String, val kind: PersonKind, val isOwner: Boolean)

/** The Delete group row. */
@Immutable
sealed interface DeleteRow {
    /**
     * The person owns the group: "Removes it for everyone. Balances must be settled first.", or, while nobody else in
     * it is on the app and a balance is open, "Removes it for everyone. Nobody else has joined it yet." (143;
     * docs/server.md 4.6).
     *
     * @param canDelete true while every member's net is 0 or nobody else has joined: the row is tappable.
     * @param nobodyElseJoined true while no member other than the person is on the app.
     */
    data class Owner(val canDelete: Boolean, val nobodyElseJoined: Boolean = false) : DeleteRow

    /**
     * Someone else owns it (45): the lock and "Only Rahul Menon can do this. Rahul is the owner."
     *
     * @param ownerName the owner's name.
     */
    data class NotOwner(val ownerName: String) : DeleteRow
}

/** A confirm dialog of Group settings (not designed). */
enum class SettingsConfirm {
    /** "Leave Flat 302?" */
    Leave,

    /** "Delete Flat 302 for everyone?" */
    Delete,
}

/**
 * The friend's link sheet (46), for a friend without the app.
 *
 * @param memberId the friend.
 * @param name their name, the sheet's title.
 * @param linkOn true when this phone made them a link (46 as drawn: "Link is on", Turn off link). False when this
 * phone made none (not designed, laid out as 46). The phone cannot know about links other members (or the person on
 * another phone) made, so the sheet says "No link from this phone", never that the link is off; it offers "Remind
 * {name}", which opens Remind on WhatsApp to make the link and send it, and Turn off link, which stops any link of the
 * friend, whoever made it.
 * @param canRemind true when the friend owes the person, so Remind on WhatsApp has something to send. Only used
 * while [linkOn] is false: otherwise "Remind {name}" is off and the note says why.
 */
@Immutable
data class FriendSheet(val memberId: String, val name: String, val linkOn: Boolean = true, val canRemind: Boolean = false)

/** Something the person did on Group settings. */
sealed interface GroupSettingsEvent {
    /** The back button. */
    data object Back : GroupSettingsEvent

    /** Typed in "Group name". */
    data class NameChanged(val text: String) : GroupSettingsEvent

    /** Left "Group name" (focus moved away, Done on the keyboard): a changed name is saved. */
    data object NameDone : GroupSettingsEvent

    /** Tapped a person under People. */
    data class PersonTapped(val memberId: String) : GroupSettingsEvent

    /** "Add someone". */
    data object AddSomeone : GroupSettingsEvent

    /** "Leave group". */
    data object Leave : GroupSettingsEvent

    /** "Delete group". */
    data object Delete : GroupSettingsEvent

    /** The confirm dialog's first choice ("Leave group", "Delete group"). */
    data object Confirm : GroupSettingsEvent

    /** The confirm dialog's second choice, Back or the scrim ("Stay", "Keep it"). */
    data object CloseConfirm : GroupSettingsEvent

    /** "Turn off link" on the friend's sheet. */
    data object TurnOffLink : GroupSettingsEvent

    /**
     * "Remind {name}" on the sheet of a friend without a link: the sheet closes and the caller opens Remind on
     * WhatsApp for [memberId], which makes the link and sends it.
     */
    data class Remind(val memberId: String) : GroupSettingsEvent

    /** "Cancel", Back or the scrim on the friend's sheet. */
    data object CloseSheet : GroupSettingsEvent
}

/** A screen Group settings' ViewModel opens once. */
sealed interface GroupSettingsEffect {
    /** Sign in: the session ended. */
    data object SignIn : GroupSettingsEffect
}
