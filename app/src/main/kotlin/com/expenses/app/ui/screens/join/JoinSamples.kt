package com.expenses.app.ui.screens.join

import com.expenses.app.groups.data.GroupKind
import com.expenses.app.groups.data.JoinPreview
import com.expenses.app.ui.screens.signin.ActionProblem
import java.time.YearMonth

/** Sample states of Join group, one per design state, with the design's names and numbers. */
object JoinSamples {
    /** design/spec/screens/02-everyday/32-join-group: Rahul added you to Flat 302; you owe ₹1,150 so far. */
    val ready: JoinGroupUiState.Ready = JoinGroupUiState.Ready(
        preview = JoinPreview(
            groupId = "b0000000-0000-4000-8000-000000000001",
            groupName = "Flat 302",
            inviterName = "Rahul",
            memberNames = listOf("Rahul", "Priya"),
            memberCount = 3,
            youOweMinor = 115_000,
            owedToYouMinor = 0,
            expensesThisMonth = 5,
            alreadyMember = false,
        ),
        month = YearMonth.of(2026, 11),
    )

    /** Not in the design: the friend's place is owed money, in a bigger group. */
    val owed: JoinGroupUiState.Ready = ready.copy(
        preview = ready.preview.copy(
            memberNames = listOf("Rahul", "Priya", "Sam", "Meera", "Kabir"),
            memberCount = 6,
            youOweMinor = 0,
            owedToYouMinor = 42_000,
            expensesThisMonth = 1,
        ),
    )

    /**
     * Not in the design: four other members, all named. Kiran sent the link, so Kiran is named first; "and 1 more" is
     * never written.
     */
    val fourOthers: JoinGroupUiState.Ready = ready.copy(
        preview = ready.preview.copy(
            groupName = "Goa trip",
            inviterName = "Kiran Rao",
            memberNames = listOf("Meera", "Rahul", "Dev", "Kiran"),
            memberCount = 5,
        ),
    )

    /** Not in the design: "Join group" was tapped offline. */
    val joinOffline: JoinGroupUiState.Ready = ready.copy(problem = ActionProblem.OFFLINE)

    /** design/spec/screens/06-family/01-join-family: Arjun invited you to Home. */
    val family: JoinGroupUiState.ReadyFamily = JoinGroupUiState.ReadyFamily(
        preview = JoinPreview(
            groupId = "b0000000-0000-4000-8000-000000000031",
            groupName = "Home",
            inviterName = "Arjun",
            memberNames = listOf("Arjun"),
            memberCount = 1,
            youOweMinor = 0,
            owedToYouMinor = 0,
            expensesThisMonth = 0,
            alreadyMember = false,
            kind = GroupKind.FAMILY,
        ),
    )

    /** 06-family/13: 12 earlier Family expenses on this phone are shared too. */
    val familyEarlier: JoinGroupUiState.ReadyFamily = family.copy(earlierCount = 12)

    /** 06-family/14: "Join Family" offline. */
    val familyOffline: JoinGroupUiState.ReadyFamily = family.copy(problem = ActionProblem.OFFLINE)

    /** 06-family/15: "Join Family" refused. */
    val familyFailed: JoinGroupUiState.ReadyFamily = family.copy(problem = ActionProblem.FAILED)

    /** The wording of 02-everyday/23 when the group could not be looked up offline. */
    val couldNotLoad: JoinGroupUiState.CouldNotLoad = JoinGroupUiState.CouldNotLoad(offline = true)
}
