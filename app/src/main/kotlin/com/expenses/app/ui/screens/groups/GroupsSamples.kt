package com.expenses.app.ui.screens.groups

/** Sample states of the Groups tab, New group and Add friend, with the names, amounts and counts of the design. */
object GroupsSamples {
    private val flat302 = GroupBalance(
        id = "flat-302",
        name = "Flat 302",
        memberCount = 3,
        balancePaise = 235_000,
        avatarColor = SampleAvatars.flat302,
    )
    private val goaTrip = GroupBalance(
        id = "goa-trip",
        name = "Goa trip",
        memberCount = 5,
        balancePaise = -80_000,
        avatarColor = SampleAvatars.goaTrip,
    )
    private val officeLunch = GroupBalance(
        id = "office-lunch",
        name = "Office lunch",
        memberCount = 8,
        balancePaise = 0,
        avatarColor = SampleAvatars.officeLunch,
    )

    /** The list as drawn on the Groups screen (20): three groups and two friends, ₹1,550 owed overall. */
    val default: GroupsUiState = GroupsUiState.Loaded(
        groups = listOf(flat302, goaTrip, officeLunch),
        friends = listOf(
            FriendBalance(id = "rahul", name = "Rahul", balancePaise = 120_000, avatarColor = SampleAvatars.rahul, groupId = "rahul-direct"),
            FriendBalance(id = "priya", name = "Priya", balancePaise = 115_000, avatarColor = SampleAvatars.priya),
        ),
    )

    /** The person owes overall (44): ₹450 across three groups. */
    val youOweOverall: GroupsUiState = GroupsUiState.Loaded(
        groups = listOf(flat302.copy(balancePaise = 35_000), goaTrip, officeLunch),
        friends = listOf(
            FriendBalance(id = "rahul", name = "Rahul", balancePaise = 20_000, avatarColor = SampleAvatars.rahul),
            FriendBalance(id = "priya", name = "Priya", balancePaise = 15_000, avatarColor = SampleAvatars.priya),
        ),
    )

    /** No groups yet (21). */
    val empty: GroupsUiState = GroupsUiState.Empty

    /** Placeholders while the groups load (22). */
    val loading: GroupsUiState = GroupsUiState.Loading

    /** The groups could not be loaded and none is on this phone (the card of 23; not drawn for this tab). */
    val couldNotLoad: GroupsUiState = GroupsUiState.CouldNotLoad

    /** Signed out (39). */
    val notSignedIn: GroupsUiState = GroupsUiState.NotSignedIn

    /** New group as drawn (26): "Goa trip" with three friends switched on. */
    val newGroup: NewGroupUiState = NewGroupUiState(
        name = "Goa trip",
        people = listOf(
            NewGroupPerson(key = "rahul", name = "Rahul Menon", onApp = true, selected = true),
            NewGroupPerson(key = "priya", name = "Priya Nair", onApp = false, selected = true),
            NewGroupPerson(key = "kiran", name = "Kiran Rao", onApp = false, selected = true),
        ),
    )

    /** New group for someone who splits with nobody yet: only the "Add someone" row. */
    val newGroupNobodyKnown: NewGroupUiState = NewGroupUiState(name = "", people = emptyList())

    /** Add friend as drawn (27). */
    val addFriend: AddFriendUiState = AddFriendUiState(name = "Kiran Rao", phone = "+91 98765 43210")

    /** Add friend with a phone number that is not one. */
    val addFriendPhoneInvalid: AddFriendUiState = AddFriendUiState(name = "Kiran Rao", phone = "98450", phoneInvalid = true)
}
